package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppLegacyRoleStore;
import com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role;
import com.rigour.tenant.iam.application.service.settings.AppLegacyRoleModels.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 只在供应链设置内预览和迁入；来源角色不能由本入口修改。 */
@RestController
@RequestMapping("/api/v1/management/supply/legacy-roles")
public final class IamAppLegacyRoleController {
    private final AppLegacyRoleStore store;

    public IamAppLegacyRoleController(AppLegacyRoleStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Source> sources() {
        return store.sources(IamAppSettingsController.actor());
    }

    @PostMapping("/{id}/import")
    public Role importRole(@PathVariable UUID id, @RequestBody Command c) {
        return store.importRole(IamAppSettingsController.actor(), id, c);
    }
}
