package com.rigour.hr.api;

import com.rigour.hr.application.port.out.HrOrganizationStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public final class HrEmployeeDepartmentController {
    private final HrOrganizationStore organization;
    public HrEmployeeDepartmentController(HrOrganizationStore organization) { this.organization=organization; }
    public record Department(Long id, Long parentId, String departmentName, int sortOrder, String statusCode) {}
    @GetMapping("/api/v1/hr/employee-departments")
    public ApiResponse<List<Department>> departments() {
        AuthorizationContext.requirePermission("hr:employee:read");
        var actor=AuthorizationContext.requireCurrent();
        if (actor.tenantId()==null) throw new com.rigour.shared.context.AuthorizationDeniedException("tenant-caller");
        return ApiResponse.success(organization.departments(actor.tenantId().toString()).stream()
                .map(d -> new Department(d.id(),d.parentId(),d.departmentName(),d.sortOrder(),d.statusCode())).toList());
    }
}
