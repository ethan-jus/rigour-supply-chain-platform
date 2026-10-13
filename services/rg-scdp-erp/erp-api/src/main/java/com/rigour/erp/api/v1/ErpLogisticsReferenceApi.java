package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpCostSourceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ErpLogisticsReferenceApi {
    @GetMapping("/api/v1/erp/logistics-cost-sources")
    ApiResponse<List<ErpCostSourceView>> sources(
            @RequestParam String legCode,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam String action);
}
