package com.rigour.tenant.iam.application.service.settings;

import java.util.List;

/** 切换检查结果以版本指纹绑定；启用时重新核验，不复用过期预览。 */
public final class AppCutoverModels {
    private AppCutoverModels() {}

    public record Issue(String code, String severity, long count, String message) {}

    public record Domain(String domain, String version, List<Issue> issues) {}

    public record Report(
            String mode, long version, String fingerprint, boolean ready, List<Issue> issues) {}

    public record PermissionPreview(
            java.util.UUID userId,
            String username,
            long applicationVersion,
            java.util.List<String> legacyPermissions,
            java.util.List<String> proposedPermissions,
            java.util.List<String> added,
            java.util.List<String> removed,
            String selectedAction,
            com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot policy,
            String unavailableReason) {}

    public record Observation(
            String userId,
            String username,
            long applicationVersion,
            String action,
            String legacyAction,
            boolean legacyAllowed,
            boolean proposedAllowed,
            String policyJson,
            long sampleCount,
            java.time.Instant observedAt) {}

    public record ObservationPage(List<Observation> items, long total, int page, int pageSize) {}

    public record DataObservation(
            String userId,
            String username,
            long applicationVersion,
            String action,
            String domain,
            String recordKey,
            boolean legacyAllowed,
            boolean proposedAllowed,
            String policyJson,
            long sampleCount,
            java.time.Instant observedAt) {}

    public record DataObservationPage(
            List<DataObservation> items, long total, int page, int pageSize) {}

    public record Command(
            long version, String fingerprint, String reason, boolean acknowledgeWarnings) {}
}
