package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrCostReferenceApi;
import com.rigour.hr.api.v1.model.HrCostEmployeeView;
import com.rigour.hr.application.service.HrCostReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class HrCostReferenceController implements HrCostReferenceApi {
    private final HrCostReferenceService service;

    public HrCostReferenceController(HrCostReferenceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<HrCostEmployeeView>> employees(String action) {
        return ApiResponse.success(service.employees(action));
    }
}
