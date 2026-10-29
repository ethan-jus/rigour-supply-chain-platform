package com.rigour.tenant.iam.api.controller.internal;

import com.rigour.shared.context.AuthorizationContext;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.tenant.iam.application.service.settings.StoreAccessService;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalStoreAccessController {
    private final StoreAccessService service;
    public InternalStoreAccessController(StoreAccessService service) { this.service = service; }
    @GetMapping("/internal/v1/iam/supply/store-access")
    public StoreAccessView access(@RequestParam String action) {
        var snapshot = service.access(AuthorizationContext.requireCurrent(), action);
        return new StoreAccessView(snapshot.tenantId(), snapshot.userId(), snapshot.action(), snapshot.version(),
                snapshot.employeeId(), snapshot.employeeCode(), snapshot.employeeName(), snapshot.departmentId(),
                snapshot.permissions(), snapshot.grants().stream().map(grant -> new StoreAccessView.Grant(
                        grant.ownerScope(), grant.departmentIds(), grant.descendants(), grant.national(),
                        grant.areas().stream().map(area -> new StoreAccessView.Area(area.version(), area.code())).toList())).toList(),
                snapshot.personalLimitEnabled(), snapshot.personalAreas().stream()
                        .map(area -> new StoreAccessView.Area(area.version(), area.code())).toList());
    }
}
