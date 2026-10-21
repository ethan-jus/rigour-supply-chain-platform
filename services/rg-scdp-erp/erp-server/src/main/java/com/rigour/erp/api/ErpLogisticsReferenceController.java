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

    public ApiResponse<String> supplier(Long id, String action) {
        return ApiResponse.success(service.supplier(id, action));
    }

    public ApiResponse<String> warehouse(Long id, String action) {
        return ApiResponse.success(service.warehouse(id, action));
    }

    public ApiResponse<List<ErpCostSourceView>> sources(
            String legCode,
            String keyword,
            List<Long> ids,
            String action,
            String cityCode,
            int begin) {
        return ApiResponse.success(service.sources(legCode, keyword, ids, action, cityCode, begin));
    }

    public ApiResponse<com.rigour.erp.api.v1.model.ErpCostOptionView> warehouseDetail(
            Long id, String action) {
        return ApiResponse.success(service.warehouseDetail(id, action));
    }
}
