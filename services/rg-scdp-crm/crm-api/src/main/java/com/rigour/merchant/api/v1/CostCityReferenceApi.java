package com.rigour.merchant.api.v1;

import com.rigour.merchant.api.v1.model.CostCityView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

public interface CostCityReferenceApi {
    String BASE_PATH = "/api/v1/crm/cost-city-references";

    @GetMapping(BASE_PATH)
    ApiResponse<List<CostCityView>> cities(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String action);
}
