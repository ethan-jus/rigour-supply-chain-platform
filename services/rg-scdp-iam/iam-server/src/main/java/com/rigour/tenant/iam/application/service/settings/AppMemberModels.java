package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AppMemberModels {
    private AppMemberModels() {}

    public record Limit(String mode, List<String> references) {}

    public record Assignment(UUID roleId, Map<UUID, Map<String, List<String>>> parameters) {}

    public record Member(
            UUID id,
            String username,
            String name,
            String kind,
            String status,
            String remark,
            long version,
            String employeeCode,
            Employee employee,
            List<Assignment> roles,
            Limit regionLimit,
            Limit warehouseLimit,
            boolean usable,
            String unavailableReason, String createdByName, java.time.Instant createdTime,
            String updatedByName, java.time.Instant updatedTime) {}

    public record Page(List<Member> items, long total, int page, int pageSize) {}

    public record Account(UUID id, String username, String status) {}

    public record Command(
            UUID existingUserId,
            String username,
            String initialPassword,
            String employeeCode,
            String status,
            String remark,
            long version,
            List<Assignment> roles,
            Limit regionLimit,
            Limit warehouseLimit,
            String bindingReason) {}

    public record VersionedMember(UUID id, long version) {}

    public record BatchCommand(
            String mode,
            List<VersionedMember> members,
            List<Assignment> roles,
            long applicationVersion) {}

    public record BatchPreview(
            long applicationVersion, List<VersionedMember> members, String mode, int roleCount) {}

    public record StatusCommand(String status, long version, String reason) {}

    public record PasswordCommand(String password, long version) {}
}
