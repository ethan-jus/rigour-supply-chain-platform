package com.rigour.tenant.iam.application.service.management;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 人员中心稳定命令与视图；订货宝字段只作为来源快照，不驱动我方主档结构。 */
public final class StaffManagementModels {

    private StaffManagementModels() {
    }

    public record PositionView(UUID id, String code, String name, String description,
                               int sortOrder, String status, long version) {
    }

    public record PositionCommand(String name, String description, int sortOrder,
                                  String status, long version) {
    }

    public record StaffView(UUID id, String staffCode, String staffName, String mobile, String email,
                            String employmentStatus, UUID primaryOrganizationId,
                            String primaryOrganizationName, UUID primaryPositionId,
                            String primaryPositionName, UUID userId, String username,
                            String userDisplayName, String recordOrigin, String remark,
                            String sourceSystem, String sourceStaffId, String sourceStaffType,
                            String sourceAccountName, String sourceTitle, String sourceBranchName,
                            String sourceRole, String sourceStatus, String sourcePresence,
                            Instant lastSeenAt, long version) {
    }

    public record StaffCommand(String staffName, String mobile, String email, String employmentStatus,
                               UUID primaryOrganizationId, UUID primaryPositionId,
                               UUID userId, String remark, long version) {
    }

    public record DhbStaffResolveRequest(String sourceTenantKey, List<String> sourceStaffIds,
                                         List<String> sourceStaffNames) {
        public DhbStaffResolveRequest {
            sourceStaffIds = sourceStaffIds == null ? List.of() : sourceStaffIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(1_000)
                    .toList();
            sourceStaffNames = sourceStaffNames == null ? List.of() : sourceStaffNames.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(1_000)
                    .toList();
        }
    }

    public record StaffDisplayRequest(List<String> staffCodes) {
        public StaffDisplayRequest {
            staffCodes = staffCodes == null ? List.of() : staffCodes.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::strip)
                    .distinct()
                    .limit(1_000)
                    .toList();
        }
    }

    public record StaffDisplayView(UUID staffId, String staffCode, String staffName,
                                   String employmentStatus, UUID primaryOrganizationId,
                                   String primaryOrganizationName, UUID primaryPositionId,
                                   String primaryPositionName) {
    }

    public record DhbStaffResolvedView(String sourceTenantKey, String sourceStaffId,
                                       UUID staffId, String staffCode, String staffName,
                                       UUID userId, String username, String userDisplayName,
                                       UUID primaryOrganizationId, String primaryOrganizationName,
                                       UUID primaryPositionId, String primaryPositionName,
                                       String employmentStatus, String sourceStaffType,
                                       String sourceRole, String sourcePresence,
                                       Instant lastSeenAt) {
    }
}
