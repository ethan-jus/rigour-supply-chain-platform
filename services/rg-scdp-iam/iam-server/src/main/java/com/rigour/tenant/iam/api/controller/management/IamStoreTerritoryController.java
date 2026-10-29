package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.model.settings.StoreTerritoryModels;
import com.rigour.tenant.iam.application.service.settings.StoreTerritoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/management/supply/store-territories")
public final class IamStoreTerritoryController {
    private final StoreTerritoryService service;
    public IamStoreTerritoryController(StoreTerritoryService service) { this.service = service; }

    @GetMapping
    public StoreTerritoryModels.Context context() {
        return service.context(IamAppSettingsController.actor());
    }

    @PutMapping("/departments/{department}")
    public ResponseEntity<Void> save(@PathVariable String department, @RequestBody StoreTerritoryModels.Update command) {
        service.save(IamAppSettingsController.actor(), department, command);
        return ResponseEntity.noContent().build();
    }
}
