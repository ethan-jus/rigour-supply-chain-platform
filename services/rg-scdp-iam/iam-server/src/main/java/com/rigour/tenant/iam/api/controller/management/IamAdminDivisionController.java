package com.rigour.tenant.iam.api.controller.management;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.application.service.settings.StoreTerritoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class IamAdminDivisionController {
    private final StoreTerritoryService service;

    public IamAdminDivisionController(StoreTerritoryService service) { this.service = service; }

    @GetMapping("/api/v1/management/supply/admin-divisions")
    public AdminDivisionCatalog catalog() {
        return service.catalog(IamAppSettingsController.actor());
    }
}
