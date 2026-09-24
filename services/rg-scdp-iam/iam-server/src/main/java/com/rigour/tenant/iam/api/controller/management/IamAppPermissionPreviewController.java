package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppPermissionPreviewStore;
import com.rigour.tenant.iam.application.service.settings.AppPermissionPreviewModels.*;

import org.springframework.web.bind.annotation.*;

/** 当前权限预览；审查人由登录态读取。 */
@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppPermissionPreviewController {
    private final AppPermissionPreviewStore store;

    public IamAppPermissionPreviewController(AppPermissionPreviewStore store) {
        this.store = store;
    }

    @GetMapping("/permission-preview")
    public PermissionPreview preview(
            @RequestParam java.util.UUID userId, @RequestParam(required = false) String action) {
        return store.preview(IamAppSettingsController.actor(), userId, action);
    }
}
