package com.rigour.tenant.iam.infrastructure.persistence.projection;

import java.time.LocalDateTime;
import java.util.UUID;

public final class StaffListRow {
    private UUID id;
    private String staffCode;
    private String staffName;
    private String mobile;
    private String email;
    private String employmentStatus;
    private UUID primaryOrganizationId;
    private String primaryOrganizationName;
    private UUID primaryPositionId;
    private String primaryPositionName;
    private UUID userId;
    private String username;
    private String userDisplayName;
    private String recordOrigin;
    private String remark;
    private String sourceSystem;
    private String sourceStaffId;
    private String sourceStaffType;
    private String sourceAccountName;
    private String sourceTitle;
    private String sourceBranchName;
    private String sourceRole;
    private String sourceStatus;
    private String sourcePresence;
    private LocalDateTime lastSeenAt;
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
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
    public String getPrimaryOrganizationName() { return primaryOrganizationName; }
    public void setPrimaryOrganizationName(String primaryOrganizationName) {
        this.primaryOrganizationName = primaryOrganizationName;
    }
    public UUID getPrimaryPositionId() { return primaryPositionId; }
    public void setPrimaryPositionId(UUID primaryPositionId) { this.primaryPositionId = primaryPositionId; }
    public String getPrimaryPositionName() { return primaryPositionName; }
    public void setPrimaryPositionName(String primaryPositionName) {
        this.primaryPositionName = primaryPositionName;
    }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserDisplayName() { return userDisplayName; }
    public void setUserDisplayName(String userDisplayName) { this.userDisplayName = userDisplayName; }
    public String getRecordOrigin() { return recordOrigin; }
    public void setRecordOrigin(String recordOrigin) { this.recordOrigin = recordOrigin; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceStaffId() { return sourceStaffId; }
    public void setSourceStaffId(String sourceStaffId) { this.sourceStaffId = sourceStaffId; }
    public String getSourceStaffType() { return sourceStaffType; }
    public void setSourceStaffType(String sourceStaffType) { this.sourceStaffType = sourceStaffType; }
    public String getSourceAccountName() { return sourceAccountName; }
    public void setSourceAccountName(String sourceAccountName) { this.sourceAccountName = sourceAccountName; }
    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    public String getSourceBranchName() { return sourceBranchName; }
    public void setSourceBranchName(String sourceBranchName) { this.sourceBranchName = sourceBranchName; }
    public String getSourceRole() { return sourceRole; }
    public void setSourceRole(String sourceRole) { this.sourceRole = sourceRole; }
    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }
    public String getSourcePresence() { return sourcePresence; }
    public void setSourcePresence(String sourcePresence) { this.sourcePresence = sourcePresence; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
