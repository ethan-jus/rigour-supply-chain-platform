package com.rigour.hr.api;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;
import com.rigour.hr.infrastructure.persistence.repository.FieldworkOrganizationQuery;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.exception.RequestValidationException;

import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;

@RestController
public class FieldworkOrganizationController {
    private final FieldworkOrganizationQuery organizations;

    public FieldworkOrganizationController(FieldworkOrganizationQuery organizations) {
        this.organizations = organizations;
    }

    @PostMapping("/internal/v1/hr/fieldwork/organization")
    public ApiResponse<List<HrFieldworkOrganization>> resolve(
            @RequestParam Instant at, @RequestBody List<String> codes) {
        var caller = authorizedCaller();
        if (codes == null
                || codes.size() > 200
                || codes.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 50))
            throw new RequestValidationException("员工范围无效");
        return ApiResponse.success(organizations.find(caller.tenantId().toString(), at, codes));
    }

    @PostMapping("/internal/v1/hr/fieldwork/organization/sales-departments")
    public ApiResponse<java.util.Map<String, String>> salesDepartments() {
        return ApiResponse.success(organizations.salesDepartments(authorizedCaller().tenantId().toString()));
    }

    private CallerIdentity authorizedCaller() {
        var caller = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(caller.principalScope()) || caller.tenantId() == null)
            throw new AuthorizationDeniedException("hr:employee:identity-read");
        AuthorizationContext.requirePermission("hr:employee:identity-read");
        return caller;
    }
}
