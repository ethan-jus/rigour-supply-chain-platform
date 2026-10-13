package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CostCityReferenceApi;
import com.rigour.merchant.api.v1.model.CostCityView;
import com.rigour.merchant.application.service.CostCityReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class CostCityReferenceController implements CostCityReferenceApi {
    private final CostCityReferenceService service;

    public CostCityReferenceController(CostCityReferenceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<List<CostCityView>> cities() {
        return ApiResponse.success(service.cities());
    }
}
