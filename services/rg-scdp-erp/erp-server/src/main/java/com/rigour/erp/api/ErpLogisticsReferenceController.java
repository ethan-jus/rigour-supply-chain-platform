package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpLogisticsReferenceApi;
import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.erp.application.service.cost.ErpLogisticsReferenceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class ErpLogisticsReferenceController implements ErpLogisticsReferenceApi {
    private final ErpLogisticsReferenceService service;

    public ErpLogisticsReferenceController(ErpLogisticsReferenceService service) {
        this.service = service;
    }

    public ApiResponse<List<ErpCostSourceView>> sources(
            String legCode, String keyword, List<Long> ids, String action) {
        return ApiResponse.success(service.sources(legCode, keyword, ids, action));
    }
}
