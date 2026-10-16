package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.CostCustomerReferenceApi;
import com.rigour.merchant.api.v1.model.CostCustomerView;
import com.rigour.merchant.application.service.CostCustomerReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class CostCustomerReferenceController implements CostCustomerReferenceApi {
    private final CostCustomerReferenceService service;

    public CostCustomerReferenceController(CostCustomerReferenceService service) {
        this.service = service;
    }

    public ApiResponse<List<CostCustomerView>> customers(
            String cityCode, String keyword, Long id, int begin, String action) {
        return ApiResponse.success(service.customers(cityCode, keyword, id, begin, action));
    }
}
