package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsCityProductSupplyApi;
import com.rigour.analytics.api.v1.model.CityProductSupplyView;
import com.rigour.analytics.application.service.CityProductSupplyService;
import com.rigour.analytics.application.service.BiDataScopeService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.web.bind.annotation.RestController;

/** 商品供货调查的只读入口。 */
@RestController
public final class AnalyticsCityProductSupplyController implements AnalyticsCityProductSupplyApi {
    private final CityProductSupplyService service;
    private final BiDataScopeService scopes;
    public AnalyticsCityProductSupplyController(CityProductSupplyService service, BiDataScopeService scopes) {
        this.service = service;
        this.scopes = scopes;
    }
    @Override public ApiResponse<CityProductSupplyView> supply(Instant from, Instant to, Long productId,
            Long warehouseId, Long skuId) {
        scopes.requireGlobalGovernance();
        return ApiResponse.success(service.supply(from, to, productId, warehouseId, skuId));
    }
}
