package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.HrCostCityReference;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface HrCostCityReferenceApi {
    String BASE_PATH = "/api/v1/hr/cost-city-references";

    @GetMapping(BASE_PATH)
    ApiResponse<List<HrCostCityReference>> cities(@RequestParam String action);
}
