package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrCostEmployeeView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface HrCostReferenceApi {
    record Department(Long id, String name) {}

    @GetMapping("/api/v1/hr/cost-references/warehouse-departments")
    ApiResponse<List<Department>> warehouseDepartments(@RequestParam String action);

    @GetMapping("/api/v1/hr/cost-references/employees")
    ApiResponse<List<HrCostEmployeeView>> employees(@RequestParam String action);
}
