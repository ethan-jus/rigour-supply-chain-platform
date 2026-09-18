package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppReferenceClient;
import com.rigour.tenant.iam.application.service.settings.AppReferenceService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppReferenceController {
    private final AppReferenceService service;

    public IamAppReferenceController(AppReferenceService service) {
        this.service = service;
    }

    @GetMapping("/scope-references")
    public List<AppReferenceClient.Reference> references(@RequestParam String dimension) {
        return service.references(IamAppSettingsController.actor(), dimension);
    }
}
