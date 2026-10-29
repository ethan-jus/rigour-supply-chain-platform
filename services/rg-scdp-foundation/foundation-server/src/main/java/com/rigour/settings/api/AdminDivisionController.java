package com.rigour.settings.api;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.settings.application.port.out.AdminDivisionStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminDivisionController {
    private final AdminDivisionStore store;
    public AdminDivisionController(AdminDivisionStore store) { this.store = store; }

    @GetMapping("/internal/v1/business-settings/admin-divisions")
    public ApiResponse<AdminDivisionCatalog> catalog(@RequestParam(required = false) String version) {
        if (!"SERVICE".equals(AuthorizationContext.requireCurrent().principalScope()))
            throw new AuthorizationDeniedException("service-caller");
        AuthorizationContext.requirePermission("settings:admin-division:read");
        if (version != null && !version.matches("[A-Za-z0-9_.-]{1,32}"))
            throw new IllegalArgumentException("行政版本格式无效");
        return ApiResponse.success(store.published(version));
    }
}
