package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrCostCityReferenceApi;
import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.hr.application.service.HrCostCityReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class HrCostCityReferenceController implements HrCostCityReferenceApi {
    private final HrCostCityReferenceService service;

    public HrCostCityReferenceController(HrCostCityReferenceService service) {
        this.service = service;
    }

    public ApiResponse<List<HrCostCityReference>> cities(String action) {
        return ApiResponse.success(service.cities(action));
    }
}
