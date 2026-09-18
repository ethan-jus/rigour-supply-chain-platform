package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppCutoverStore;
import com.rigour.tenant.iam.application.service.settings.AppCutoverModels.*;
import com.rigour.tenant.iam.application.service.settings.AppSettingsModels.Context;

import org.springframework.web.bind.annotation.*;

/** 启用范围控制入口；审查人由登录态读取，不能由请求指定。 */
@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppCutoverController {
    private final AppCutoverStore store;

    public IamAppCutoverController(AppCutoverStore store) {
        this.store = store;
    }

    @GetMapping("/readiness")
    public Report inspect() {
        return store.inspect(IamAppSettingsController.actor());
    }

    @GetMapping("/permission-preview")
    public PermissionPreview preview(
            @RequestParam java.util.UUID userId, @RequestParam(required = false) String action) {
        return store.preview(IamAppSettingsController.actor(), userId, action);
    }

    @GetMapping("/authorization-observations")
    public ObservationPage observations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return store.observations(IamAppSettingsController.actor(), page, size);
    }

    @GetMapping("/data-observations")
    public DataObservationPage dataObservations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return store.dataObservations(IamAppSettingsController.actor(), page, size);
    }

    @PostMapping("/activate")
    public Context activate(@RequestBody Command c) {
        return store.activate(IamAppSettingsController.actor(), c);
    }
}
