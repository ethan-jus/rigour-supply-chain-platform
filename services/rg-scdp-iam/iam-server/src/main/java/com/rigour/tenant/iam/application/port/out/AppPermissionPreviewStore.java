package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppPermissionPreviewModels.PermissionPreview;

public interface AppPermissionPreviewStore {
    PermissionPreview preview(Actor actor, java.util.UUID userId, String action);
}
