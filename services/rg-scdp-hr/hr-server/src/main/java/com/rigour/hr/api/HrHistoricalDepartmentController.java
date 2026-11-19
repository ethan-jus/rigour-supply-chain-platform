package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrHistoricalDepartmentApi;
import com.rigour.hr.application.service.HrHistoricalDepartmentService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrHistoricalDepartmentController implements HrHistoricalDepartmentApi {
    private final HrHistoricalDepartmentService service;

    public HrHistoricalDepartmentController(HrHistoricalDepartmentService service) {
        this.service = service;
    }

    public ApiResponse<List<Attribution>> resolveDepartments(List<Query> queries) {
        return ApiResponse.success(service.resolve(queries));
    }
}
