package com.rigour.tenant.iam.application.service.settings;

public final class AppPermissionPreviewModels {
    private AppPermissionPreviewModels() {}

    public record PermissionPreview(
            java.util.UUID userId,
            String username,
            long applicationVersion,
            java.util.List<String> permissions,
            String selectedAction,
            com.rigour.tenant.iam.application.model.settings.AppAuthorizationSnapshot policy,
            String unavailableReason) {}
}
